import React from "react"
import { connect } from "react-redux"

import Immutable from "seamless-immutable"
import { Flex, Box } from "rebass"

import Alert from "../components/Alert"
import Job from "../components/Job"

import * as focusActions from "../store/focus/actions"
import * as jobActions from "../store/job/actions"

class AlertContainer extends React.Component {
  constructor(props) {
    super(props)
    this.state = {
      job: Immutable({
        highPrice: "",
        lowPrice: "",
        message: "Alert"
      })
    }
  }

  onChange = job => {
    this.setState({
      job: job
    })
  }

  onFocus = focusedProperty => {
    this.props.dispatch(
      focusActions.setUpdateAction(value => {
        console.log("Set focus to" + focusedProperty)
        this.setState(prev => ({
          job: prev.job.merge({
            [focusedProperty]: value
          })
        }))
      })
    )
  }

  createJob = () => {
    const isValidNumber = val => !isNaN(val) && val !== "" && val > 0
    const highPriceValid =
      this.state.job.highPrice && isValidNumber(this.state.job.highPrice)
    const lowPriceValid =
      this.state.job.lowPrice && isValidNumber(this.state.job.lowPrice)

    const tickTrigger = {
      exchange: this.props.coin.exchange,
      counter: this.props.coin.counter,
      base: this.props.coin.base
    }

    return {
      jobType: "OneCancelsOther",
      tickTrigger: tickTrigger,
      verbose: false,
      low: lowPriceValid
        ? {
            thresholdAsString: String(this.state.job.lowPrice),
            job: {
              jobType: "Alert",
              message:
                "Price of " +
                this.props.coin.name +
                " dropped below [" +
                this.state.job.lowPrice +
                "]: " +
                this.state.job.message
            }
          }
        : null,
      high: highPriceValid
        ? {
            thresholdAsString: String(this.state.job.highPrice),
            job: {
              jobType: "Alert",
              message:
                "Price of " +
                this.props.coin.name +
                " rose above [" +
                this.state.job.highPrice +
                "]: " +
                this.state.job.message
            }
          }
        : null
    }
  }

  onSubmit = async () => {
    this.props.dispatch(jobActions.submitJob(this.createJob()))
  }

  render() {
    const isValidNumber = val => !isNaN(val) && val !== "" && val > 0
    const highPriceValid =
      this.state.job.highPrice && isValidNumber(this.state.job.highPrice)
    const lowPriceValid =
      this.state.job.lowPrice && isValidNumber(this.state.job.lowPrice)
    const messageValid = this.state.job.message !== ""

    return (
      <Flex flexWrap="wrap">
        <Box width={1 / 3}>
          <Alert
            job={this.state.job}
            onChange={this.onChange}
            onFocus={this.onFocus}
            onSubmit={this.onSubmit}
            highPriceValid={highPriceValid}
            lowPriceValid={lowPriceValid}
            messageValid={messageValid}
          />
        </Box>
        <Box width={2 / 3}>
          <Job job={this.createJob()} />
        </Box>
      </Flex>
    )
  }
}

function mapStateToProps(state) {
  return {
    auth: state.auth
  }
}

export default connect(mapStateToProps)(AlertContainer)